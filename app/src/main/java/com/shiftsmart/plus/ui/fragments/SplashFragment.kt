package com.shiftsmart.plus.ui.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.navigation.fragment.findNavController
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.FragmentSplashBinding
import com.shiftsmart.plus.utils.SharedPref

class SplashFragment : Fragment() {


    private lateinit var mBinding: FragmentSplashBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment

        mBinding= FragmentSplashBinding.inflate(inflater,container,false)

        val fadeInAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in)
        val scaleUpAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.scale_up)
        // Start animations
        mBinding.ivLog.startAnimation(fadeInAnimation)
        mBinding.ivLog.startAnimation(scaleUpAnimation)
        Handler(Looper.getMainLooper()).postDelayed({
            if (isAdded) { // Ensure fragment is still attached to the activity
                val userModel = SharedPref.getInstance(requireContext())?.getUser()
                Log.i("TAG", "onAnimationEnd: userModel: $userModel")

                val destinationId = if (userModel != null && userModel?.isActive == true) {
                    R.id.action_splashFragment_to_homeFragment
                } else {
                    R.id.action_splashFragment_to_loginFragment
                }

                findNavController().navigate(destinationId)
            } else {
                Log.w("SplashFragment", "Fragment not attached, navigation aborted.")
            }
        }, 3000)


        return mBinding.root
    }

}